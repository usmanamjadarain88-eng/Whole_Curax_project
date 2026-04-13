from utils.vercel_adapter import make_handler
from utils.route_handlers import update_medicine, delete_medicine


def _patch(body, query, headers):
    mid = (query.get("medicine_id") or "").strip()
    if not mid:
        return 400, {"message": "medicine_id required"}
    return update_medicine(mid, body, query, headers)


def _delete(body, query, headers):
    mid = (query.get("medicine_id") or "").strip()
    if not mid:
        return 400, {"message": "medicine_id required"}
    return delete_medicine(mid, body, query, headers)


class handler(make_handler(patch_fn=_patch, delete_fn=_delete)):
    pass
