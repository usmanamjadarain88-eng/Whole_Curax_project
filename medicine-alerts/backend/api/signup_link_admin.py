from utils.vercel_adapter import make_handler
from utils.route_handlers import signup_link_admin

class handler(make_handler(
    post_fn=signup_link_admin,
)):
    pass