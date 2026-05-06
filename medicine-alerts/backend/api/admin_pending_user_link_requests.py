from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_pending_user_link_requests

class handler(
    make_handler(
        get_fn=admin_pending_user_link_requests,
    )
):
    pass