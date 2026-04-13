from utils.vercel_adapter import make_handler
from utils.route_handlers import signup_start

class handler(
    make_handler(
        post_fn=signup_start,
    )
):
    pass