from utils.vercel_adapter import make_handler
from utils.route_handlers import signup_link_request_status

class handler(
    make_handler(
        get_fn=signup_link_request_status,
    )
):
    pass