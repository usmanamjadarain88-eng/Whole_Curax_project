from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_accept_user_link_request

class handler(
    make_handler(
        post_fn=admin_accept_user_link_request,
    )
):
    pass