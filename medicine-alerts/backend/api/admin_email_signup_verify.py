from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_email_signup_verify

class handler(
    make_handler(
        post_fn=admin_email_signup_verify,
    )
):
    pass