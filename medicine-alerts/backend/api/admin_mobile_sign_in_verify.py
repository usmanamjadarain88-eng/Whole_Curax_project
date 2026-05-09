from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_mobile_sign_in_verify

class handler(
    make_handler(
        post_fn=admin_mobile_sign_in_verify,
    )
):
    pass