from utils.vercel_adapter import make_handler
from utils.route_handlers import signup_verify_email

class handler(make_handler(
    post_fn=signup_verify_email,
)):
    pass