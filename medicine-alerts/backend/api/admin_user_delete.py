from utils.vercel_adapter import make_handler
from utils.route_handlers import delete_admin_user


def _delete(body, query, headers):
    uid = (query.get("user_id") or "").strip()
    if not uid:
        return 400, {"message": "user_id required"}
    return delete_admin_user(uid, body, query, headers)


handler = make_handler(delete_fn=_delete)
