from utils.vercel_adapter import make_handler
from utils.route_handlers import root

class handler(make_handler(
    get_fn=root,
)):
    pass