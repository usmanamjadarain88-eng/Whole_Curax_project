from utils.vercel_adapter import make_handler
from utils.route_handlers import user_desktop_by_code

class handler(make_handler(
    post_fn=user_desktop_by_code,
)):
    pass