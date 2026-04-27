from utils.vercel_adapter import make_handler
from utils.route_handlers import signup_sign_in

class handler(
    make_handler(
        post_fn=signup_sign_in,
    )
):
    pass