from fastapi.testclient import TestClient
from main import app


def test_money_lifecycle_is_idempotent_and_server_authorized():
    with TestClient(app) as client:
        # Register + confirm email using the development-only test code.
        r = client.post("/auth/register", json={"email": "buyer@example.com", "username": "Buyer", "password": "A-strong-password-123"})
        assert r.status_code == 201, r.text
        code = r.json()["development_code"]
        r = client.post("/auth/verify", json={"email": "buyer@example.com", "code": code})
        assert r.status_code == 200, r.text
        buyer_token = r.json()["access_token"]
        buyer_headers = {"Authorization": f"Bearer {buyer_token}"}

        # A normal account cannot touch the developer dashboard.
        assert client.get("/admin/settings", headers=buyer_headers).status_code == 403

        admin_login = client.post("/auth/login", json={"email": "admin-test@example.com", "password": "VeryStrongTestPassword-12345"})
        assert admin_login.status_code == 200, admin_login.text
        admin_headers = {"Authorization": f"Bearer {admin_login.json()['access_token']}"}

        # Add an explicit test product and fund only this test account through an audited admin action.
        product = client.post("/admin/products", headers=admin_headers, json={
            "game": "Free Fire", "name": "Test pack", "price_piasters": 10000,
            "fee_enabled": True, "enabled": True
        })
        assert product.status_code == 201, product.text
        user_id = r.json()["user"]["id"] if "user" in r.json() else client.get("/me", headers=buyer_headers).json()["id"]
        add = client.post(f"/admin/users/{user_id}/balance", headers=admin_headers,
                          json={"amount_piasters": 20000, "reason": "Test setup funding"})
        assert add.status_code == 200, add.text

        order_body = {"product_id": product.json()["id"], "player_id": "PLAYER-TEST-111", "idempotency_key": "purchase-test-key-0001"}
        order1 = client.post("/orders", headers=buyer_headers, json=order_body)
        assert order1.status_code == 201, order1.text
        assert order1.json()["fee"]["piasters"] == 30  # 0.3% of 10,000 piastres
        order2 = client.post("/orders", headers=buyer_headers, json=order_body)
        assert order2.status_code == 201, order2.text
        assert order2.json()["id"] == order1.json()["id"]
        tx = client.get("/transactions", headers=buyer_headers).json()
        assert len([x for x in tx if x["kind"] == "game_purchase"]) == 1
        assert len([x for x in tx if x["kind"] == "product_fee"]) == 1

        complete = client.post(f"/admin/orders/{order1.json()['id']}/review", headers=admin_headers,
                               json={"decision": "complete", "reason": "Test provider confirmation"})
        assert complete.status_code == 200, complete.text
        assert complete.json()["status"] == "completed"

        # Proofs go to private storage, and unique references cannot be reused.
        proof = {"proof": ("proof.jpg", b"\xff\xd8\xfflocal test image bytes", "image/jpeg")}
        deposit_fields = {"channel": "vodafone_cash", "amount_piasters": "20000", "sender_phone": "01000000123", "reference": "TEST-REF-001"}
        dep = client.post("/deposits", headers=buyer_headers, data=deposit_fields, files=proof)
        assert dep.status_code == 201, dep.text
        proof_again = {"proof": ("proof.jpg", b"\xff\xd8\xfflocal test image bytes", "image/jpeg")}
        dup = client.post("/deposits", headers=buyer_headers, data={**deposit_fields, "reference": "TEST-REF-001"}, files=proof_again)
        assert dup.status_code == 409
        accepted = client.post(f"/admin/deposits/{dep.json()['id']}/review", headers=admin_headers,
                               json={"decision": "accept"})
        assert accepted.status_code == 200, accepted.text
        assert accepted.json()["status"] == "accepted"
        duplicate_approval = client.post(f"/admin/deposits/{dep.json()['id']}/review", headers=admin_headers,
                                         json={"decision": "accept"})
        assert duplicate_approval.status_code == 409

        # A rejected game order refunds the charge and reverses its separately logged product fee.
        refund_order = client.post("/orders", headers=buyer_headers, json={
            "product_id": product.json()["id"], "player_id": "PLAYER-TEST-222", "idempotency_key": "purchase-test-key-0002"
        })
        assert refund_order.status_code == 201, refund_order.text
        rejected_order = client.post(f"/admin/orders/{refund_order.json()['id']}/review", headers=admin_headers,
                                     json={"decision": "reject", "reason": "Test refund path"})
        assert rejected_order.status_code == 200, rejected_order.text
        tx_after_refund = client.get("/transactions", headers=buyer_headers).json()
        assert len([x for x in tx_after_refund if x["kind"] == "product_fee_reversal"]) == 1

        # Withdrawal reserves funds and requires a real transfer reference to finish.
        wd = client.post("/withdrawals", headers=buyer_headers,
                         json={"channel": "vodafone_cash", "amount_piasters": 5000, "recipient": "01000000999"})
        assert wd.status_code == 201, wd.text
        assert wd.json()["fee"]["piasters"] == 15
        missing_ref = client.post(f"/admin/withdrawals/{wd.json()['id']}/review", headers=admin_headers,
                                  json={"decision": "accept"})
        assert missing_ref.status_code == 400
        processing = client.post(f"/admin/withdrawals/{wd.json()['id']}/review", headers=admin_headers,
                                 json={"decision": "processing"})
        assert processing.status_code == 200
        finished = client.post(f"/admin/withdrawals/{wd.json()['id']}/review", headers=admin_headers,
                               json={"decision": "accept", "transfer_reference": "REAL-TEST-TRANSFER-88"})
        assert finished.status_code == 200, finished.text
        assert finished.json()["status"] == "completed"
        wallet = client.get("/wallet", headers=buyer_headers)
        assert wallet.status_code == 200
        assert wallet.json()["held"]["piasters"] == 0
        assert wallet.json()["available"]["piasters"] >= 0
        assert wallet.json()["recorded_fees"]["piasters"] == 45  # one retained product fee + one completed withdrawal fee
