from utils.vercel_adapter import make_handler
from utils.route_handlers import connect_to_admin

class handler(make_handler(
    post_fn=connect_to_admin,
)):
    pass