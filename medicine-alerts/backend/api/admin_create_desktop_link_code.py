from utils.vercel_adapter import make_handler
from utils.route_handlers import create_desktop_link_code

class handler(
    make_handler(
        post_fn=create_desktop_link_code,
    )
):
    pass