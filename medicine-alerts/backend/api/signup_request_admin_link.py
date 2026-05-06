from utils.vercel_adapter import make_handler
from utils.route_handlers import signup_request_admin_link

class handler(
    make_handler(
        post_fn=signup_request_admin_link,
    )
):
    pass