from __future__ import annotations

import hashlib
from contextlib import asynccontextmanager
import json
import logging
import os
import secrets
import smtplib
import uuid
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from pathlib import Path
from typing import Any, Generator, Literal

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerificationError, VerifyMismatchError
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator
from sqlalchemy import (Boolean, CheckConstraint, DateTime, ForeignKey, Integer, String,
                        Text, UniqueConstraint, create_engine, select, func)
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

load_dotenv()
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("hamo-ultimate")

APP_ENV = os.getenv("APP_ENV", "development").lower()
JWT_SECRET = os.getenv("JWT_SECRET", "development-only-change-this-secret-before-deploying")
JWT_ALGORITHM = "HS256"
JWT_HOURS = int(os.getenv("JWT_HOURS", "24"))
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./hamo.db")
DATA_DIR = Path(os.getenv("DATA_DIR", "./data")).resolve()
PROOF_DIR = DATA_DIR / "proofs"
PROOF_DIR.mkdir(parents=True, exist_ok=True)
MAX_UPLOAD_BYTES = 5 * 1024 * 1024
PASSWORD_HASHER = PasswordHasher()

if APP_ENV == "production" and (len(JWT_SECRET) < 32 or JWT_SECRET.startswith("development-only")):
    raise RuntimeError("Production requires a strong JWT_SECRET of at least 32 characters.")

engine_kwargs: dict[str, Any] = {"pool_pre_ping": True}
if DATABASE_URL.startswith("sqlite"):
    engine_kwargs["connect_args"] = {"check_same_thread": False, "timeout": 30}
engine = create_engine(DATABASE_URL, **engine_kwargs)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(254), unique=True, index=True)
    username: Mapped[str] = mapped_column(String(48))
    password_hash: Mapped[str] = mapped_column(String(255))
    role: Mapped[str] = mapped_column(String(16), default="user", index=True)
    is_verified: Mapped[bool] = mapped_column(Boolean, default=False)
    is_blocked: Mapped[bool] = mapped_column(Boolean, default=False)
    balance_available: Mapped[int] = mapped_column(Integer, default=0)
    balance_held: Mapped[int] = mapped_column(Integer, default=0)
    xp: Mapped[int] = mapped_column(Integer, default=0)
    level: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class EmailChallenge(Base):
    __tablename__ = "email_challenges"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    purpose: Mapped[str] = mapped_column(String(16), index=True)
    code_hash: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    used: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class Product(Base):
    __tablename__ = "products"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    game: Mapped[str] = mapped_column(String(64), index=True)
    name: Mapped[str] = mapped_column(String(120))
    price_piasters: Mapped[int] = mapped_column(Integer)
    fee_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    supplier_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    __table_args__ = (CheckConstraint("price_piasters > 0", name="ck_product_price_positive"),)


class GameOrder(Base):
    __tablename__ = "game_orders"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    product_id: Mapped[int] = mapped_column(ForeignKey("products.id"))
    player_id: Mapped[str] = mapped_column(String(80))
    base_amount_piasters: Mapped[int] = mapped_column(Integer)
    fee_piasters: Mapped[int] = mapped_column(Integer)
    total_piasters: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(20), default="review", index=True)
    idempotency_key: Mapped[str] = mapped_column(String(100), unique=True)
    status_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class DepositRequest(Base):
    __tablename__ = "deposit_requests"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    channel: Mapped[str] = mapped_column(String(24), default="vodafone_cash")
    amount_piasters: Mapped[int] = mapped_column(Integer)
    sender_phone: Mapped[str] = mapped_column(String(32))
    reference: Mapped[str] = mapped_column(String(100), unique=True, index=True)
    proof_filename: Mapped[str] = mapped_column(String(180))
    status: Mapped[str] = mapped_column(String(20), default="review", index=True)
    status_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    reviewed_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class WithdrawalRequest(Base):
    __tablename__ = "withdrawal_requests"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    channel: Mapped[str] = mapped_column(String(24))
    amount_piasters: Mapped[int] = mapped_column(Integer)
    fee_piasters: Mapped[int] = mapped_column(Integer)
    net_piasters: Mapped[int] = mapped_column(Integer)
    recipient: Mapped[str] = mapped_column(String(140))
    status: Mapped[str] = mapped_column(String(20), default="review", index=True)
    status_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    transfer_reference: Mapped[str | None] = mapped_column(String(120), nullable=True)
    reviewed_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class LedgerEntry(Base):
    __tablename__ = "ledger_entries"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    event_key: Mapped[str] = mapped_column(String(140), unique=True, index=True)
    kind: Mapped[str] = mapped_column(String(48), index=True)
    amount_piasters: Mapped[int] = mapped_column(Integer)
    available_delta: Mapped[int] = mapped_column(Integer, default=0)
    held_delta: Mapped[int] = mapped_column(Integer, default=0)
    reference: Mapped[str | None] = mapped_column(String(140), nullable=True)
    note: Mapped[str] = mapped_column(String(255), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class XPEvent(Base):
    __tablename__ = "xp_events"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    event_key: Mapped[str] = mapped_column(String(140), unique=True)
    points: Mapped[int] = mapped_column(Integer)
    reason: Mapped[str] = mapped_column(String(255))
    actor_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class Setting(Base):
    __tablename__ = "settings"
    key: Mapped[str] = mapped_column(String(80), primary_key=True)
    value: Mapped[str] = mapped_column(String(300))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    updated_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)


class AuditLog(Base):
    __tablename__ = "audit_logs"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    actor_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    action: Mapped[str] = mapped_column(String(100), index=True)
    target_type: Mapped[str] = mapped_column(String(48))
    target_id: Mapped[str] = mapped_column(String(80))
    details: Mapped[str] = mapped_column(Text, default="{}")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class Notification(Base):
    __tablename__ = "notifications"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    title: Mapped[str] = mapped_column(String(120))
    body: Mapped[str] = mapped_column(String(500))
    is_read: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)


class RegisterBody(BaseModel):
    email: EmailStr
    username: str = Field(min_length=2, max_length=48)
    password: str = Field(min_length=10, max_length=128)


class LoginBody(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class CodeBody(BaseModel):
    email: EmailStr
    code: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")


class ResetPasswordBody(CodeBody):
    new_password: str = Field(min_length=10, max_length=128)


class ProductBody(BaseModel):
    game: str = Field(min_length=2, max_length=64)
    name: str = Field(min_length=2, max_length=120)
    price_piasters: int = Field(gt=0, le=100_000_000)
    fee_enabled: bool = True
    enabled: bool = True
    supplier_name: str | None = Field(default=None, max_length=120)


class ProductPatch(BaseModel):
    game: str | None = Field(default=None, min_length=2, max_length=64)
    name: str | None = Field(default=None, min_length=2, max_length=120)
    price_piasters: int | None = Field(default=None, gt=0, le=100_000_000)
    fee_enabled: bool | None = None
    enabled: bool | None = None
    supplier_name: str | None = Field(default=None, max_length=120)


class OrderBody(BaseModel):
    product_id: int
    player_id: str = Field(min_length=2, max_length=80)
    idempotency_key: str = Field(min_length=12, max_length=100)


class WithdrawalBody(BaseModel):
    channel: Literal["vodafone_cash", "binance"]
    amount_piasters: int = Field(gt=0, le=100_000_000)
    recipient: str = Field(min_length=5, max_length=140)


class ReviewBody(BaseModel):
    decision: Literal["accept", "reject", "processing", "complete", "refund", "cancel"]
    reason: str | None = Field(default=None, max_length=500)
    transfer_reference: str | None = Field(default=None, max_length=120)


class AdminBalanceBody(BaseModel):
    amount_piasters: int = Field(ge=-100_000_000, le=100_000_000)
    reason: str = Field(min_length=3, max_length=250)

    @field_validator("amount_piasters")
    @classmethod
    def nonzero_balance_adjustment(cls, value: int) -> int:
        if value == 0:
            raise ValueError("Adjustment must not be zero")
        return value


class AdminXPBody(BaseModel):
    points: int = Field(ge=-1_000_000, le=1_000_000)
    reason: str = Field(min_length=3, max_length=250)

    @field_validator("points")
    @classmethod
    def nonzero_xp_adjustment(cls, value: int) -> int:
        if value == 0:
            raise ValueError("Adjustment must not be zero")
        return value


class SettingsBody(BaseModel):
    values: dict[str, int | bool | str]


class BlockBody(BaseModel):
    blocked: bool


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def money(value: int) -> dict[str, Any]:
    return {"piasters": int(value), "egp": round(int(value) / 100, 2), "formatted": f"{int(value) / 100:.2f} EGP"}


def get_setting(db: Session, key: str, default: Any) -> Any:
    row = db.get(Setting, key)
    if row is None:
        return default
    value = row.value
    if isinstance(default, bool):
        return value.lower() == "true"
    if isinstance(default, int):
        return int(value)
    return value


def ensure_default_settings(db: Session) -> None:
    defaults: dict[str, Any] = {
        "product_fee_bps": 30,
        "withdrawal_fee_bps": 30,
        "binance_fee_bps": 200,
        "minimum_withdrawal_piasters": 1000,
        "maximum_withdrawal_piasters": 5_000_000,
        "level2_xp": 500,
        "level3_xp": 1500,
        "xp_completed_game_order": 10,
        "xp_completed_withdrawal": 0,
        "binance_enabled": False,
        "profit_enabled": True,
        "profit_level_required": 3,
        "vodafone_cash_number": "01092642484",
    }
    for key, value in defaults.items():
        if db.get(Setting, key) is None:
            db.add(Setting(key=key, value=str(value).lower() if isinstance(value, bool) else str(value)))
    db.commit()


def record_ledger(db: Session, user: User, event_key: str, kind: str, amount: int,
                  available_delta: int = 0, held_delta: int = 0,
                  reference: str | None = None, note: str = "") -> LedgerEntry:
    existing = db.scalar(select(LedgerEntry).where(LedgerEntry.event_key == event_key))
    if existing:
        return existing
    if user.balance_available + available_delta < 0 or user.balance_held + held_delta < 0:
        raise HTTPException(status_code=409, detail="Insufficient funds or invalid held-balance change.")
    user.balance_available += available_delta
    user.balance_held += held_delta
    entry = LedgerEntry(user_id=user.id, event_key=event_key, kind=kind, amount_piasters=amount,
                        available_delta=available_delta, held_delta=held_delta,
                        reference=reference, note=note[:255])
    db.add(entry)
    return entry


def notify(db: Session, user_id: int, title: str, body: str) -> None:
    db.add(Notification(user_id=user_id, title=title[:120], body=body[:500]))


def audit(db: Session, actor_id: int | None, action: str, target_type: str, target_id: str,
          details: dict[str, Any] | None = None) -> None:
    db.add(AuditLog(actor_id=actor_id, action=action, target_type=target_type,
                    target_id=str(target_id), details=json.dumps(details or {}, ensure_ascii=False)))


def recalc_level(db: Session, user: User) -> tuple[int, int]:
    old_level = user.level
    level2 = get_setting(db, "level2_xp", 500)
    level3 = get_setting(db, "level3_xp", 1500)
    user.level = 3 if user.xp >= level3 else 2 if user.xp >= level2 else 1
    if user.level > old_level:
        notify(db, user.id, "ترقية جديدة 👑", f"مبروك! وصلت إلى المستوى {user.level}.")
        if user.level >= get_setting(db, "profit_level_required", 3) and get_setting(db, "profit_enabled", True):
            notify(db, user.id, "تم فتح نظام الربح المتكامل", "تم فتح القسم وفق إعدادات التطبيق. المزايا لا تعني أرباحًا مضمونة.")
    return old_level, user.level


def award_xp(db: Session, user: User, event_key: str, points: int, reason: str, actor_id: int | None = None) -> bool:
    if points <= 0:
        return False
    if db.scalar(select(XPEvent.id).where(XPEvent.event_key == event_key)) is not None:
        return False
    db.add(XPEvent(user_id=user.id, event_key=event_key, points=points, reason=reason[:255], actor_id=actor_id))
    user.xp = max(0, user.xp + points)
    recalc_level(db, user)
    return True


def user_json(user: User) -> dict[str, Any]:
    return {"id": user.id, "email": user.email, "username": user.username, "role": user.role,
            "is_verified": user.is_verified, "level": user.level, "xp": user.xp,
            "balance_available": money(user.balance_available), "balance_held": money(user.balance_held),
            "created_at": user.created_at.isoformat() if user.created_at else None}


def product_json(db: Session, product: Product) -> dict[str, Any]:
    fee = calculate_fee(product.price_piasters, get_setting(db, "product_fee_bps", 30)) if product.fee_enabled else 0
    return {"id": product.id, "game": product.game, "name": product.name,
            "price": money(product.price_piasters), "fee": money(fee),
            "total": money(product.price_piasters + fee), "fee_enabled": product.fee_enabled,
            "enabled": product.enabled, "supplier_name": product.supplier_name}


def calculate_fee(amount: int, bps: int) -> int:
    return max(0, (amount * max(0, bps) + 5000) // 10000)


def order_json(db: Session, order: GameOrder) -> dict[str, Any]:
    product = db.get(Product, order.product_id)
    return {"id": order.id, "product_id": order.product_id,
            "product_name": product.name if product else "Deleted product", "game": product.game if product else "",
            "player_id": order.player_id, "base_amount": money(order.base_amount_piasters),
            "fee": money(order.fee_piasters), "total": money(order.total_piasters),
            "status": order.status, "status_note": order.status_note,
            "created_at": order.created_at.isoformat(), "updated_at": order.updated_at.isoformat()}


def deposit_json(db: Session, dep: DepositRequest) -> dict[str, Any]:
    user = db.get(User, dep.user_id)
    return {"id": dep.id, "user_id": dep.user_id, "username": user.username if user else "",
            "email": user.email if user else "", "channel": dep.channel,
            "amount": money(dep.amount_piasters), "sender_phone": dep.sender_phone,
            "reference": dep.reference, "has_proof": bool(dep.proof_filename),
            "status": dep.status, "status_note": dep.status_note,
            "created_at": dep.created_at.isoformat(), "updated_at": dep.updated_at.isoformat()}


def withdrawal_json(db: Session, wd: WithdrawalRequest) -> dict[str, Any]:
    user = db.get(User, wd.user_id)
    return {"id": wd.id, "user_id": wd.user_id, "username": user.username if user else "",
            "channel": wd.channel, "amount": money(wd.amount_piasters), "fee": money(wd.fee_piasters),
            "net": money(wd.net_piasters), "recipient": wd.recipient, "status": wd.status,
            "status_note": wd.status_note, "transfer_reference": wd.transfer_reference,
            "created_at": wd.created_at.isoformat(), "updated_at": wd.updated_at.isoformat()}


def send_email(to_email: str, subject: str, body: str) -> bool:
    host = os.getenv("SMTP_HOST")
    username = os.getenv("SMTP_USERNAME")
    password = os.getenv("SMTP_PASSWORD")
    if not host or not username or not password:
        return False
    try:
        msg = EmailMessage()
        msg["Subject"] = subject
        msg["From"] = os.getenv("SMTP_FROM", username)
        msg["To"] = to_email
        msg.set_content(body)
        with smtplib.SMTP(host, int(os.getenv("SMTP_PORT", "587")), timeout=12) as server:
            server.starttls()
            server.login(username, password)
            server.send_message(msg)
        return True
    except Exception:
        log.exception("Unable to send email")
        return False


def challenge_expired(challenge: EmailChallenge) -> bool:
    expiry = challenge.expires_at
    if expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=timezone.utc)
    return expiry < now_utc()


def make_challenge(db: Session, user: User, purpose: str) -> str:
    code = f"{secrets.randbelow(1_000_000):06d}"
    digest = hashlib.sha256((user.email.lower() + ":" + purpose + ":" + code + JWT_SECRET).encode()).hexdigest()
    db.add(EmailChallenge(user_id=user.id, purpose=purpose, code_hash=digest,
                          expires_at=now_utc().replace(tzinfo=None) + timedelta(minutes=15)))
    db.flush()
    title = "رمز تأكيد HAMO ULTIMATE" if purpose == "verify" else "رمز استعادة كلمة المرور"
    sent = send_email(user.email, title, f"رمزك هو: {code}\nينتهي خلال 15 دقيقة. إذا لم تطلبه فتجاهل الرسالة.")
    if not sent and APP_ENV == "production":
        raise HTTPException(status_code=503, detail="Email delivery is not configured. Contact the administrator.")
    if not sent:
        log.warning("Development-only %s code for %s: %s", purpose, user.email, code)
    return code


def get_current_user(authorization: str | None = Header(default=None), db: Session = Depends(get_db)) -> User:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Authentication required.")
    token = authorization[7:]
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user_id = int(payload["sub"])
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired token.")
    user = db.get(User, user_id)
    if not user or user.is_blocked:
        raise HTTPException(status_code=401, detail="Account unavailable.")
    if not user.is_verified:
        raise HTTPException(status_code=403, detail="Verify your email before using the app.")
    return user


def get_admin(user: User = Depends(get_current_user)) -> User:
    if user.role != "admin":
        raise HTTPException(status_code=403, detail="Developer access required.")
    return user


def create_access_token(user: User) -> str:
    return jwt.encode({"sub": str(user.id), "exp": now_utc() + timedelta(hours=JWT_HOURS),
                       "iat": now_utc()}, JWT_SECRET, algorithm=JWT_ALGORITHM)


def maybe_bootstrap_admin() -> None:
    email = os.getenv("ADMIN_EMAIL", "").strip().lower()
    password = os.getenv("ADMIN_PASSWORD", "")
    if not email or not password:
        log.warning("No bootstrap admin configured. Set ADMIN_EMAIL and ADMIN_PASSWORD in environment.")
        return
    with SessionLocal() as db:
        existing = db.scalar(select(User).where(User.email == email))
        if existing:
            if existing.role != "admin":
                raise RuntimeError("ADMIN_EMAIL already belongs to a non-admin account. Choose a fresh admin email or provision the role securely; automatic promotion is disabled.")
            ensure_default_settings(db)
            return
        if len(password) < 14:
            log.warning("ADMIN_PASSWORD should be at least 14 characters.")
        admin = User(email=email, username=os.getenv("ADMIN_USERNAME", "HAMO Developer"),
                     password_hash=PASSWORD_HASHER.hash(password), role="admin", is_verified=True)
        db.add(admin)
        db.commit()
        ensure_default_settings(db)
        log.info("Bootstrap admin configured for %s", email)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        ensure_default_settings(db)
    maybe_bootstrap_admin()
    yield


app = FastAPI(title="HAMO ULTIMATE API", version="1.0.0", description="Secure API for HAMO ULTIMATE.", lifespan=lifespan)
if APP_ENV != "production":
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "HAMO ULTIMATE API"}


@app.get("/config")
def public_config(db: Session = Depends(get_db)) -> dict[str, Any]:
    return {"brand": "HAMO ULTIMATE", "currency": "EGP",
            "vodafone_cash_number": get_setting(db, "vodafone_cash_number", "01092642484"),
            "product_fee_bps": get_setting(db, "product_fee_bps", 30),
            "withdrawal_fee_bps": get_setting(db, "withdrawal_fee_bps", 30),
            "binance_fee_bps": get_setting(db, "binance_fee_bps", 200),
            "binance_enabled": get_setting(db, "binance_enabled", False),
            "profit_enabled": get_setting(db, "profit_enabled", True),
            "level2_xp": get_setting(db, "level2_xp", 500),
            "level3_xp": get_setting(db, "level3_xp", 1500),
            "minimum_withdrawal_piasters": get_setting(db, "minimum_withdrawal_piasters", 1000),
            "maximum_withdrawal_piasters": get_setting(db, "maximum_withdrawal_piasters", 5_000_000)}


@app.post("/auth/register", status_code=201)
def register(body: RegisterBody, db: Session = Depends(get_db)) -> dict[str, Any]:
    email = str(body.email).lower()
    if db.scalar(select(User.id).where(User.email == email)):
        raise HTTPException(status_code=409, detail="Email is already registered.")
    user = User(email=email, username=body.username.strip(), password_hash=PASSWORD_HASHER.hash(body.password))
    db.add(user)
    try:
        db.flush()
        code = make_challenge(db, user, "verify")
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="Email is already registered.")
    result: dict[str, Any] = {"message": "Account created. Verify your email with the 6-digit code."}
    if APP_ENV != "production":
        result["development_code"] = code
    return result


@app.post("/auth/verify")
def verify_email(body: CodeBody, db: Session = Depends(get_db)) -> dict[str, Any]:
    user = db.scalar(select(User).where(User.email == str(body.email).lower()))
    if not user:
        raise HTTPException(status_code=404, detail="Account not found.")
    challenge = db.scalar(select(EmailChallenge).where(EmailChallenge.user_id == user.id,
                         EmailChallenge.purpose == "verify", EmailChallenge.used.is_(False))
                         .order_by(EmailChallenge.id.desc()))
    if not challenge or challenge_expired(challenge):
        raise HTTPException(status_code=400, detail="Code is invalid or expired.")
    digest = hashlib.sha256((user.email.lower() + ":verify:" + body.code + JWT_SECRET).encode()).hexdigest()
    if not secrets.compare_digest(digest, challenge.code_hash):
        raise HTTPException(status_code=400, detail="Code is invalid or expired.")
    challenge.used = True
    user.is_verified = True
    notify(db, user.id, "أهلًا بك في HAMO ULTIMATE", "تم تأكيد حسابك بنجاح.")
    db.commit()
    token = create_access_token(user)
    return {"access_token": token, "token_type": "bearer", "user": user_json(user)}


@app.post("/auth/login")
def login(body: LoginBody, db: Session = Depends(get_db)) -> dict[str, Any]:
    user = db.scalar(select(User).where(User.email == str(body.email).lower()))
    if not user or user.is_blocked:
        raise HTTPException(status_code=401, detail="Email or password is incorrect.")
    try:
        PASSWORD_HASHER.verify(user.password_hash, body.password)
    except (VerificationError, VerifyMismatchError):
        raise HTTPException(status_code=401, detail="Email or password is incorrect.")
    if not user.is_verified:
        raise HTTPException(status_code=403, detail="Email is not verified.")
    return {"access_token": create_access_token(user), "token_type": "bearer", "user": user_json(user)}


@app.post("/auth/forgot-password")
def forgot_password(body: dict[str, str], db: Session = Depends(get_db)) -> dict[str, str]:
    email = str(body.get("email", "")).lower().strip()
    user = db.scalar(select(User).where(User.email == email))
    result = {"message": "If the account exists, a recovery code will be sent."}
    if user and user.is_verified:
        code = make_challenge(db, user, "reset")
        db.commit()
        if APP_ENV != "production":
            result["development_code"] = code
    return result


@app.post("/auth/reset-password")
def reset_password(body: ResetPasswordBody, db: Session = Depends(get_db)) -> dict[str, str]:
    user = db.scalar(select(User).where(User.email == str(body.email).lower()))
    if not user:
        raise HTTPException(status_code=400, detail="Code is invalid or expired.")
    challenge = db.scalar(select(EmailChallenge).where(EmailChallenge.user_id == user.id,
                         EmailChallenge.purpose == "reset", EmailChallenge.used.is_(False))
                         .order_by(EmailChallenge.id.desc()))
    digest = hashlib.sha256((user.email.lower() + ":reset:" + body.code + JWT_SECRET).encode()).hexdigest()
    if not challenge or challenge_expired(challenge) or not secrets.compare_digest(digest, challenge.code_hash):
        raise HTTPException(status_code=400, detail="Code is invalid or expired.")
    challenge.used = True
    user.password_hash = PASSWORD_HASHER.hash(body.new_password)
    db.commit()
    return {"message": "Password updated. Please sign in again."}


@app.get("/me")
def get_me(user: User = Depends(get_current_user)) -> dict[str, Any]:
    return user_json(user)


@app.get("/products")
def list_products(game: str | None = None, db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    stmt = select(Product).where(Product.enabled.is_(True)).order_by(Product.game, Product.id)
    if game:
        stmt = stmt.where(Product.game == game)
    return [product_json(db, p) for p in db.scalars(stmt).all()]


@app.post("/orders", status_code=201)
def create_order(body: OrderBody, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    existing = db.scalar(select(GameOrder).where(GameOrder.idempotency_key == body.idempotency_key))
    if existing:
        if existing.user_id != user.id:
            raise HTTPException(status_code=409, detail="Idempotency key already used.")
        return order_json(db, existing)
    if len(body.player_id.strip()) < 2 or len(body.idempotency_key.strip()) < 12:
        raise HTTPException(status_code=422, detail="Player ID and idempotency key are required.")
    product = db.get(Product, body.product_id)
    if not product or not product.enabled:
        raise HTTPException(status_code=404, detail="Product not found or disabled.")
    base = product.price_piasters
    fee = calculate_fee(base, get_setting(db, "product_fee_bps", 30)) if product.fee_enabled else 0
    total = base + fee
    try:
        # With PostgreSQL, FOR UPDATE serializes competing wallet changes. SQLite is for local development only.
        locked_user = db.scalar(select(User).where(User.id == user.id).with_for_update())
        if not locked_user or locked_user.balance_available < total:
            raise HTTPException(status_code=409, detail="Insufficient wallet balance.")
        order = GameOrder(user_id=user.id, product_id=product.id, player_id=body.player_id.strip(),
                          base_amount_piasters=base, fee_piasters=fee, total_piasters=total,
                          status="review", idempotency_key=body.idempotency_key)
        db.add(order)
        db.flush()
        record_ledger(db, locked_user, f"order:{order.id}:charge", "game_purchase", total,
                      available_delta=-total, reference=order.id,
                      note=f"Purchase {product.game} / {product.name}")
        if fee:
            # Separate fee record for transparent accounting; no second wallet debit.
            db.add(LedgerEntry(user_id=locked_user.id, event_key=f"order:{order.id}:fee", kind="product_fee",
                               amount_piasters=fee, reference=order.id, note="Included in the total purchase charge"))
        notify(db, user.id, "تم استلام طلب الشحن", f"طلبك {order.id} قيد المراجعة. لم يتم تأكيد التنفيذ بعد.")
        db.commit()
        db.refresh(order)
        return order_json(db, order)
    except IntegrityError:
        db.rollback()
        existing = db.scalar(select(GameOrder).where(GameOrder.idempotency_key == body.idempotency_key))
        if existing and existing.user_id == user.id:
            return order_json(db, existing)
        raise HTTPException(status_code=409, detail="Duplicate request or database conflict.")


@app.get("/orders")
def my_orders(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    rows = db.scalars(select(GameOrder).where(GameOrder.user_id == user.id).order_by(GameOrder.created_at.desc())).all()
    return [order_json(db, x) for x in rows]


@app.post("/orders/{order_id}/cancel")
def cancel_order(order_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    order = db.get(GameOrder, order_id)
    if not order or order.user_id != user.id:
        raise HTTPException(status_code=404, detail="Order not found.")
    if order.status != "review":
        raise HTTPException(status_code=409, detail="Only orders awaiting review can be cancelled.")
    locked_user = db.scalar(select(User).where(User.id == user.id).with_for_update())
    record_ledger(db, locked_user, f"order:{order.id}:refund", "game_refund", order.total_piasters,
                  available_delta=order.total_piasters, reference=order.id, note="User cancelled before processing")
    if order.fee_piasters:
        db.add(LedgerEntry(user_id=locked_user.id, event_key=f"order:{order.id}:fee-refund", kind="product_fee_reversal",
                           amount_piasters=-order.fee_piasters, reference=order.id, note="Product fee reversed with purchase refund"))
    order.status, order.status_note, order.updated_at = "cancelled", "Cancelled by user", now_utc()
    notify(db, user.id, "تم إلغاء الطلب", "تمت إعادة المبلغ إلى محفظتك.")
    db.commit()
    return order_json(db, order)


@app.get("/wallet")
def wallet(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    rows = db.scalars(select(LedgerEntry).where(LedgerEntry.user_id == user.id)).all()
    deposits = sum(x.amount_piasters for x in rows if x.kind == "deposit_accepted")
    purchases = sum(x.amount_piasters for x in rows if x.kind == "game_purchase")
    withdrawals = sum(x.amount_piasters for x in rows if x.kind == "withdrawal_completed")
    fees = sum(x.amount_piasters for x in rows if x.kind in ("product_fee", "withdrawal_fee", "product_fee_reversal"))
    return {"available": money(user.balance_available), "held": money(user.balance_held),
            "total_deposits": money(deposits), "total_purchases": money(purchases),
            "total_withdrawals": money(withdrawals), "recorded_fees": money(fees),
            "xp": user.xp, "level": user.level,
            "level2_xp": get_setting(db, "level2_xp", 500), "level3_xp": get_setting(db, "level3_xp", 1500),
            "profit_unlocked": user.level >= get_setting(db, "profit_level_required", 3) and get_setting(db, "profit_enabled", True)}


@app.get("/transactions")
def transactions(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    rows = db.scalars(select(LedgerEntry).where(LedgerEntry.user_id == user.id).order_by(LedgerEntry.created_at.desc()).limit(300)).all()
    return [{"id": x.id, "kind": x.kind, "amount": money(x.amount_piasters),
             "available_delta": money(x.available_delta), "held_delta": money(x.held_delta),
             "reference": x.reference, "note": x.note, "created_at": x.created_at.isoformat()} for x in rows]


@app.post("/deposits", status_code=201)
async def create_deposit(channel: Literal["vodafone_cash", "binance"] = Form(...),
                         amount_piasters: int = Form(..., gt=0, le=100_000_000),
                         sender_phone: str = Form(..., min_length=5, max_length=32),
                         reference: str = Form(..., min_length=4, max_length=100),
                         proof: UploadFile = File(...),
                         user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    if channel == "binance" and not get_setting(db, "binance_enabled", False):
        raise HTTPException(status_code=403, detail="Binance requests are disabled until a supported provider is configured.")
    reference_clean = reference.strip().upper()
    sender_clean = sender_phone.strip()
    if len(reference_clean) < 4 or len(sender_clean) < 5:
        raise HTTPException(status_code=422, detail="A valid transaction reference and sender identifier are required.")
    if db.scalar(select(DepositRequest.id).where(DepositRequest.reference == reference_clean)):
        raise HTTPException(status_code=409, detail="This transaction reference has already been submitted.")
    content_type = (proof.content_type or "").lower()
    if content_type not in ("image/jpeg", "image/png", "image/webp"):
        raise HTTPException(status_code=415, detail="Proof must be a JPEG, PNG, or WEBP image.")
    data = await proof.read(MAX_UPLOAD_BYTES + 1)
    if len(data) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Proof image must be 5 MB or smaller.")
    if not data:
        raise HTTPException(status_code=400, detail="Proof image is empty.")
    # Validate magic bytes; client-supplied content-type alone is not trusted.
    valid_signature = ((content_type == "image/jpeg" and data.startswith(b"\xff\xd8\xff")) or
                       (content_type == "image/png" and data.startswith(b"\x89PNG\r\n\x1a\n")) or
                       (content_type == "image/webp" and len(data) >= 12 and data.startswith(b"RIFF") and data[8:12] == b"WEBP"))
    if not valid_signature:
        raise HTTPException(status_code=415, detail="The uploaded file does not match its declared image type.")
    suffix = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[content_type]
    filename = f"{uuid.uuid4().hex}{suffix}"
    path = PROOF_DIR / filename
    path.write_bytes(data)
    row = DepositRequest(user_id=user.id, channel=channel, amount_piasters=amount_piasters,
                         sender_phone=sender_clean, reference=reference_clean,
                         proof_filename=filename, status="review")
    db.add(row)
    try:
        db.flush()
        notify(db, user.id, "تم إرسال طلب الإيداع", f"الطلب {row.id} قيد المراجعة. لم يُضف الرصيد بعد.")
        admins = db.scalars(select(User).where(User.role == "admin", User.is_blocked.is_(False))).all()
        for admin in admins:
            notify(db, admin.id, "طلب إيداع جديد", f"طلب {row.id} بمبلغ {amount_piasters / 100:.2f} جنيه.")
        db.commit()
    except IntegrityError:
        db.rollback()
        path.unlink(missing_ok=True)
        raise HTTPException(status_code=409, detail="This transaction reference has already been submitted.")
    return deposit_json(db, row)


@app.get("/deposits")
def my_deposits(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    rows = db.scalars(select(DepositRequest).where(DepositRequest.user_id == user.id).order_by(DepositRequest.created_at.desc())).all()
    return [deposit_json(db, x) for x in rows]


@app.post("/withdrawals", status_code=201)
def create_withdrawal(body: WithdrawalBody, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    if body.channel == "binance" and not get_setting(db, "binance_enabled", False):
        raise HTTPException(status_code=403, detail="Binance requests are disabled until a supported provider is configured.")
    if len(body.recipient.strip()) < 5:
        raise HTTPException(status_code=422, detail="Recipient details are required.")
    minimum = get_setting(db, "minimum_withdrawal_piasters", 1000)
    maximum = get_setting(db, "maximum_withdrawal_piasters", 5_000_000)
    if body.amount_piasters < minimum or body.amount_piasters > maximum:
        raise HTTPException(status_code=400, detail=f"Amount must be between {money(minimum)['formatted']} and {money(maximum)['formatted']}.")
    bps = get_setting(db, "binance_fee_bps", 200) if body.channel == "binance" else get_setting(db, "withdrawal_fee_bps", 30)
    fee = calculate_fee(body.amount_piasters, bps)
    if fee >= body.amount_piasters:
        raise HTTPException(status_code=400, detail="Fee must be less than the requested amount.")
    locked_user = db.scalar(select(User).where(User.id == user.id).with_for_update())
    if not locked_user or locked_user.balance_available < body.amount_piasters:
        raise HTTPException(status_code=409, detail="Insufficient available wallet balance.")
    row = WithdrawalRequest(user_id=user.id, channel=body.channel, amount_piasters=body.amount_piasters,
                            fee_piasters=fee, net_piasters=body.amount_piasters-fee,
                            recipient=body.recipient.strip(), status="review")
    db.add(row)
    db.flush()
    record_ledger(db, locked_user, f"withdrawal:{row.id}:hold", "withdrawal_hold", body.amount_piasters,
                  available_delta=-body.amount_piasters, held_delta=body.amount_piasters,
                  reference=row.id, note="Withdrawal amount reserved pending manual execution")
    notify(db, user.id, "طلب السحب قيد المراجعة", f"الصافي المتوقع {money(row.net_piasters)['formatted']}. لم يتم التحويل بعد.")
    for admin in db.scalars(select(User).where(User.role == "admin", User.is_blocked.is_(False))).all():
        notify(db, admin.id, "طلب سحب جديد", f"طلب {row.id} يحتاج مراجعة.")
    db.commit()
    return withdrawal_json(db, row)


@app.get("/withdrawals")
def my_withdrawals(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    rows = db.scalars(select(WithdrawalRequest).where(WithdrawalRequest.user_id == user.id).order_by(WithdrawalRequest.created_at.desc())).all()
    return [withdrawal_json(db, x) for x in rows]


@app.get("/requests")
def my_requests(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    orders = db.scalars(select(GameOrder).where(GameOrder.user_id == user.id).order_by(GameOrder.created_at.desc()).limit(100)).all()
    deposits = db.scalars(select(DepositRequest).where(DepositRequest.user_id == user.id).order_by(DepositRequest.created_at.desc()).limit(100)).all()
    withdrawals = db.scalars(select(WithdrawalRequest).where(WithdrawalRequest.user_id == user.id).order_by(WithdrawalRequest.created_at.desc()).limit(100)).all()
    return {"orders": [order_json(db, x) for x in orders], "deposits": [deposit_json(db, x) for x in deposits],
            "withdrawals": [withdrawal_json(db, x) for x in withdrawals]}


@app.get("/xp")
def xp_history(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, Any]:
    rows = db.scalars(select(XPEvent).where(XPEvent.user_id == user.id).order_by(XPEvent.created_at.desc()).limit(300)).all()
    return {"level": user.level, "xp": user.xp,
            "level2_xp": get_setting(db, "level2_xp", 500), "level3_xp": get_setting(db, "level3_xp", 1500),
            "events": [{"points": x.points, "reason": x.reason, "event_key": x.event_key,
                        "created_at": x.created_at.isoformat()} for x in rows]}


@app.get("/notifications")
def list_notifications(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    rows = db.scalars(select(Notification).where(Notification.user_id == user.id).order_by(Notification.created_at.desc()).limit(100)).all()
    return [{"id": n.id, "title": n.title, "body": n.body, "is_read": n.is_read, "created_at": n.created_at.isoformat()} for n in rows]


@app.post("/notifications/{notification_id}/read")
def read_notification(notification_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict[str, bool]:
    notification = db.get(Notification, notification_id)
    if not notification or notification.user_id != user.id:
        raise HTTPException(status_code=404, detail="Notification not found.")
    notification.is_read = True
    db.commit()
    return {"ok": True}


# ----- Developer endpoints: every route below is role-protected on the server. -----
@app.get("/admin/overview")
def admin_overview(admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    pending_deposits = db.scalar(select(func.count()).select_from(DepositRequest).where(DepositRequest.status == "review")) or 0
    pending_withdrawals = db.scalar(select(func.count()).select_from(WithdrawalRequest).where(WithdrawalRequest.status == "review")) or 0
    pending_orders = db.scalar(select(func.count()).select_from(GameOrder).where(GameOrder.status.in_(["review", "processing"]))) or 0
    return {"users": db.scalar(select(func.count()).select_from(User).where(User.role == "user")) or 0,
            "pending_deposits": pending_deposits, "pending_withdrawals": pending_withdrawals,
            "pending_orders": pending_orders, "total_available": money(db.scalar(select(func.sum(User.balance_available))) or 0),
            "total_held": money(db.scalar(select(func.sum(User.balance_held))) or 0)}


@app.get("/admin/users")
def admin_users(q: str = "", admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    stmt = select(User).order_by(User.created_at.desc()).limit(200)
    if q.strip():
        needle = f"%{q.strip().lower()}%"
        stmt = select(User).where((func.lower(User.email).like(needle)) | (func.lower(User.username).like(needle))).order_by(User.created_at.desc()).limit(200)
    return [{"id": u.id, "email": u.email, "username": u.username, "role": u.role,
             "is_verified": u.is_verified, "is_blocked": u.is_blocked, "xp": u.xp, "level": u.level,
             "balance_available": money(u.balance_available), "balance_held": money(u.balance_held)}
            for u in db.scalars(stmt).all()]


@app.post("/admin/users/{user_id}/block")
def admin_block_user(user_id: int, body: BlockBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    user = db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found.")
    if user.role == "admin" and body.blocked:
        raise HTTPException(status_code=400, detail="Cannot block an administrator here.")
    user.is_blocked = body.blocked
    audit(db, admin.id, "user_block_update", "user", str(user.id), {"blocked": body.blocked})
    db.commit()
    return {"id": user.id, "is_blocked": user.is_blocked}


@app.post("/admin/users/{user_id}/balance")
def admin_adjust_balance(user_id: int, body: AdminBalanceBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    user = db.scalar(select(User).where(User.id == user_id).with_for_update())
    if not user:
        raise HTTPException(status_code=404, detail="User not found.")
    event_key = f"admin-balance:{uuid.uuid4()}"
    record_ledger(db, user, event_key, "admin_adjustment", abs(body.amount_piasters),
                  available_delta=body.amount_piasters, reference=str(user.id), note=body.reason)
    audit(db, admin.id, "balance_adjustment", "user", str(user.id), {"amount_piasters": body.amount_piasters, "reason": body.reason})
    notify(db, user.id, "تعديل على رصيدك", f"تم تعديل الرصيد: {money(body.amount_piasters)['formatted']}. السبب: {body.reason}")
    db.commit()
    return user_json(user)


@app.post("/admin/users/{user_id}/xp")
def admin_adjust_xp(user_id: int, body: AdminXPBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    user = db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="User not found.")
    event = XPEvent(user_id=user.id, event_key=f"admin-xp:{uuid.uuid4()}", points=body.points,
                    reason=body.reason, actor_id=admin.id)
    db.add(event)
    user.xp = max(0, user.xp + body.points)
    recalc_level(db, user)
    audit(db, admin.id, "xp_adjustment", "user", str(user.id), {"points": body.points, "reason": body.reason})
    db.commit()
    return user_json(user)


@app.post("/admin/products", status_code=201)
def admin_create_product(body: ProductBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    if len(body.game.strip()) < 2 or len(body.name.strip()) < 2:
        raise HTTPException(status_code=422, detail="Game and product names are required.")
    product = Product(game=body.game.strip(), name=body.name.strip(), price_piasters=body.price_piasters,
                      fee_enabled=body.fee_enabled, enabled=body.enabled, supplier_name=body.supplier_name)
    db.add(product)
    db.flush()
    audit(db, admin.id, "product_created", "product", str(product.id), body.model_dump())
    db.commit()
    return product_json(db, product)


@app.patch("/admin/products/{product_id}")
def admin_patch_product(product_id: int, body: ProductPatch, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    product = db.get(Product, product_id)
    if not product:
        raise HTTPException(status_code=404, detail="Product not found.")
    changes = body.model_dump(exclude_unset=True)
    if "game" in changes and changes["game"] is not None and len(changes["game"].strip()) < 2:
        raise HTTPException(status_code=422, detail="Game name is required.")
    if "name" in changes and changes["name"] is not None and len(changes["name"].strip()) < 2:
        raise HTTPException(status_code=422, detail="Product name is required.")
    for key, value in changes.items():
        setattr(product, key, value.strip() if isinstance(value, str) and key != "supplier_name" else value)
    audit(db, admin.id, "product_updated", "product", str(product.id), changes)
    db.commit()
    return product_json(db, product)


@app.delete("/admin/products/{product_id}")
def admin_delete_product(product_id: int, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, bool]:
    product = db.get(Product, product_id)
    if not product:
        raise HTTPException(status_code=404, detail="Product not found.")
    # Soft-disable so old order records retain their product relation and price history.
    product.enabled = False
    audit(db, admin.id, "product_disabled", "product", str(product.id), {})
    db.commit()
    return {"ok": True}


@app.get("/admin/products")
def admin_list_products(admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    return [product_json(db, p) for p in db.scalars(select(Product).order_by(Product.game, Product.id)).all()]


@app.get("/admin/orders")
def admin_orders(status_filter: str | None = None, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    stmt = select(GameOrder).order_by(GameOrder.created_at.desc()).limit(300)
    if status_filter == "active":
        stmt = stmt.where(GameOrder.status.in_(["review", "processing"]))
    elif status_filter:
        stmt = stmt.where(GameOrder.status == status_filter)
    return [order_json(db, x) for x in db.scalars(stmt).all()]


@app.post("/admin/orders/{order_id}/review")
def admin_review_order(order_id: str, body: ReviewBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    order = db.get(GameOrder, order_id)
    if not order:
        raise HTTPException(status_code=404, detail="Order not found.")
    if order.status in ("completed", "rejected", "cancelled", "refunded"):
        raise HTTPException(status_code=409, detail="Order is already in a terminal state.")
    if body.decision == "processing":
        order.status = "processing"
    elif body.decision in ("reject", "cancel", "refund"):
        order.status = "rejected" if body.decision == "reject" else "cancelled" if body.decision == "cancel" else "refunded"
        buyer = db.scalar(select(User).where(User.id == order.user_id).with_for_update())
        record_ledger(db, buyer, f"order:{order.id}:refund", "game_refund", order.total_piasters,
                      available_delta=order.total_piasters, reference=order.id,
                      note=body.reason or "Order not completed; refund")
        if order.fee_piasters:
            db.add(LedgerEntry(user_id=buyer.id, event_key=f"order:{order.id}:fee-refund", kind="product_fee_reversal",
                               amount_piasters=-order.fee_piasters, reference=order.id, note="Product fee reversed with purchase refund"))
    elif body.decision == "complete":
        order.status = "completed"
        buyer = db.get(User, order.user_id)
        points = get_setting(db, "xp_completed_game_order", 10)
        award_xp(db, buyer, f"order:{order.id}:xp", points, "Completed game order")
    else:
        raise HTTPException(status_code=400, detail="Unsupported decision.")
    order.status_note = body.reason
    order.updated_at = now_utc()
    notify(db, order.user_id, "تحديث طلب الشحن", f"الطلب {order.id}: {order.status}. {body.reason or ''}")
    audit(db, admin.id, "order_review", "order", order.id, {"decision": body.decision, "reason": body.reason})
    db.commit()
    return order_json(db, order)


@app.get("/admin/deposits")
def admin_deposits(status_filter: str | None = "review", admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    stmt = select(DepositRequest).order_by(DepositRequest.created_at.desc()).limit(300)
    if status_filter:
        stmt = stmt.where(DepositRequest.status == status_filter)
    return [deposit_json(db, x) for x in db.scalars(stmt).all()]


@app.get("/admin/deposits/{deposit_id}/proof")
def admin_deposit_proof(deposit_id: str, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> FileResponse:
    dep = db.get(DepositRequest, deposit_id)
    if not dep:
        raise HTTPException(status_code=404, detail="Deposit not found.")
    path = PROOF_DIR / dep.proof_filename
    if not path.exists():
        raise HTTPException(status_code=404, detail="Proof file is not available.")
    media_type = "image/png" if path.suffix == ".png" else "image/webp" if path.suffix == ".webp" else "image/jpeg"
    return FileResponse(path, media_type=media_type, filename=f"proof-{dep.id}{path.suffix}")


@app.post("/admin/deposits/{deposit_id}/review")
def admin_review_deposit(deposit_id: str, body: ReviewBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    dep = db.scalar(select(DepositRequest).where(DepositRequest.id == deposit_id).with_for_update())
    if not dep:
        raise HTTPException(status_code=404, detail="Deposit not found.")
    if dep.status != "review":
        raise HTTPException(status_code=409, detail="Deposit request has already been reviewed.")
    dep.reviewed_by = admin.id
    dep.updated_at = now_utc()
    dep.status_note = body.reason
    if body.decision == "accept":
        buyer = db.scalar(select(User).where(User.id == dep.user_id).with_for_update())
        record_ledger(db, buyer, f"deposit:{dep.id}:credit", "deposit_accepted", dep.amount_piasters,
                      available_delta=dep.amount_piasters, reference=dep.reference, note=f"Verified {dep.channel} deposit")
        dep.status = "accepted"
        notify(db, buyer.id, "تم قبول الإيداع", f"تمت إضافة {money(dep.amount_piasters)['formatted']} إلى محفظتك.")
    elif body.decision == "reject":
        dep.status = "rejected"
        notify(db, dep.user_id, "تم رفض الإيداع", body.reason or "يرجى التواصل مع الدعم لمعرفة التفاصيل.")
    else:
        raise HTTPException(status_code=400, detail="Deposit decision must be accept or reject.")
    audit(db, admin.id, "deposit_review", "deposit", dep.id, {"decision": body.decision, "reason": body.reason})
    db.commit()
    return deposit_json(db, dep)


@app.get("/admin/withdrawals")
def admin_withdrawals(status_filter: str | None = "active", admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    stmt = select(WithdrawalRequest).order_by(WithdrawalRequest.created_at.desc()).limit(300)
    if status_filter == "active":
        stmt = stmt.where(WithdrawalRequest.status.in_(["review", "processing"]))
    elif status_filter:
        stmt = stmt.where(WithdrawalRequest.status == status_filter)
    return [withdrawal_json(db, x) for x in db.scalars(stmt).all()]


@app.post("/admin/withdrawals/{withdrawal_id}/review")
def admin_review_withdrawal(withdrawal_id: str, body: ReviewBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    wd = db.scalar(select(WithdrawalRequest).where(WithdrawalRequest.id == withdrawal_id).with_for_update())
    if not wd:
        raise HTTPException(status_code=404, detail="Withdrawal not found.")
    if wd.status not in ("review", "processing"):
        raise HTTPException(status_code=409, detail="Withdrawal request has already been reviewed.")
    buyer = db.scalar(select(User).where(User.id == wd.user_id).with_for_update())
    wd.reviewed_by = admin.id
    wd.updated_at = now_utc()
    wd.status_note = body.reason
    if body.decision == "accept":
        if not body.transfer_reference or len(body.transfer_reference.strip()) < 4:
            raise HTTPException(status_code=400, detail="Actual transfer reference is required before marking a payout completed.")
        wd.transfer_reference = body.transfer_reference.strip()
        wd.status = "completed"
        record_ledger(db, buyer, f"withdrawal:{wd.id}:release", "withdrawal_completed", wd.amount_piasters,
                      held_delta=-wd.amount_piasters, reference=wd.transfer_reference, note="Admin confirmed actual transfer")
        if wd.fee_piasters:
            # Fee is separately visible for accounting; it is part of the gross amount, not an extra debit.
            db.add(LedgerEntry(user_id=buyer.id, event_key=f"withdrawal:{wd.id}:fee", kind="withdrawal_fee",
                               amount_piasters=wd.fee_piasters, reference=wd.id, note="Fee included in requested amount"))
        points = get_setting(db, "xp_completed_withdrawal", 0)
        award_xp(db, buyer, f"withdrawal:{wd.id}:xp", points, "Completed withdrawal")
        notify(db, buyer.id, "اكتمل السحب", f"تم تسجيل التحويل. مرجع التحويل: {wd.transfer_reference}")
    elif body.decision == "reject":
        wd.status = "rejected"
        record_ledger(db, buyer, f"withdrawal:{wd.id}:release", "withdrawal_released", wd.amount_piasters,
                      available_delta=wd.amount_piasters, held_delta=-wd.amount_piasters,
                      reference=wd.id, note=body.reason or "Withdrawal rejected; hold released")
        notify(db, buyer.id, "تم رفض السحب", f"أُعيد المبلغ المحجوز إلى رصيدك. {body.reason or ''}")
    elif body.decision == "processing":
        # Processing state is intentionally not a terminal decision. Keep the hold in place.
        wd.status = "processing"
        notify(db, buyer.id, "طلب السحب قيد التنفيذ", "ما زال المبلغ محجوزًا حتى تأكيد التحويل أو رفض الطلب.")
    else:
        raise HTTPException(status_code=400, detail="Unsupported withdrawal decision.")
    audit(db, admin.id, "withdrawal_review", "withdrawal", wd.id,
          {"decision": body.decision, "reason": body.reason, "transfer_reference": body.transfer_reference})
    db.commit()
    return withdrawal_json(db, wd)


@app.get("/admin/settings")
def admin_settings(admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    defaults = {"product_fee_bps": 30, "withdrawal_fee_bps": 30, "binance_fee_bps": 200,
                "minimum_withdrawal_piasters": 1000, "maximum_withdrawal_piasters": 5_000_000,
                "level2_xp": 500, "level3_xp": 1500, "xp_completed_game_order": 10,
                "xp_completed_withdrawal": 0, "binance_enabled": False, "profit_enabled": True,
                "profit_level_required": 3, "vodafone_cash_number": "01092642484"}
    return {key: get_setting(db, key, default) for key, default in defaults.items()}


@app.put("/admin/settings")
def update_admin_settings(body: SettingsBody, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> dict[str, Any]:
    allowed = {"product_fee_bps": (int, 0, 5000), "withdrawal_fee_bps": (int, 0, 5000),
               "binance_fee_bps": (int, 0, 5000), "minimum_withdrawal_piasters": (int, 1, 100_000_000),
               "maximum_withdrawal_piasters": (int, 1, 100_000_000), "level2_xp": (int, 1, 10_000_000),
               "level3_xp": (int, 1, 10_000_000), "xp_completed_game_order": (int, 0, 1_000_000),
               "xp_completed_withdrawal": (int, 0, 1_000_000), "binance_enabled": (bool, None, None),
               "profit_enabled": (bool, None, None), "profit_level_required": (int, 1, 10),
               "vodafone_cash_number": (str, None, None)}
    for key, value in body.values.items():
        if key not in allowed:
            raise HTTPException(status_code=400, detail=f"Unsupported setting: {key}")
        typ, low, high = allowed[key]
        if typ is int:
            if isinstance(value, bool) or not isinstance(value, int) or value < low or value > high:
                raise HTTPException(status_code=400, detail=f"Invalid value for {key}")
        elif typ is bool and not isinstance(value, bool):
            raise HTTPException(status_code=400, detail=f"Invalid value for {key}")
        elif typ is str and (not isinstance(value, str) or not value.strip() or len(value) > 40):
            raise HTTPException(status_code=400, detail=f"Invalid value for {key}")
        row = db.get(Setting, key)
        value_string = str(value).lower() if isinstance(value, bool) else str(value)
        if row:
            row.value = value_string
            row.updated_at = now_utc()
            row.updated_by = admin.id
        else:
            db.add(Setting(key=key, value=value_string, updated_by=admin.id))
    if "level3_xp" in body.values and "level2_xp" not in body.values:
        if get_setting(db, "level2_xp", 500) >= int(body.values["level3_xp"]):
            raise HTTPException(status_code=400, detail="Level 3 XP threshold must be higher than Level 2.")
    if "level2_xp" in body.values and int(body.values["level2_xp"]) >= get_setting(db, "level3_xp", 1500):
        if "level3_xp" not in body.values or int(body.values["level2_xp"]) >= int(body.values["level3_xp"]):
            raise HTTPException(status_code=400, detail="Level 2 XP threshold must be lower than Level 3.")
    audit(db, admin.id, "settings_updated", "settings", "global", body.values)
    db.commit()
    return admin_settings(admin, db)


@app.get("/admin/audit")
def admin_audit(limit: int = 100, admin: User = Depends(get_admin), db: Session = Depends(get_db)) -> list[dict[str, Any]]:
    limit = max(1, min(limit, 300))
    rows = db.scalars(select(AuditLog).order_by(AuditLog.created_at.desc()).limit(limit)).all()
    return [{"id": x.id, "actor_id": x.actor_id, "action": x.action, "target_type": x.target_type,
             "target_id": x.target_id, "details": json.loads(x.details or "{}"), "created_at": x.created_at.isoformat()} for x in rows]


# Initialize the schema on import so ASGI test clients and uvicorn share predictable startup behavior.
Base.metadata.create_all(bind=engine)
with SessionLocal() as _db:
    ensure_default_settings(_db)
