from utils.vercel_adapter import make_handler
from utils.route_handlers import user_create_desktop_link_code

class handler(
    make_handler(
        post_fn=user_create_desktop_link_code,
    )
):
    pass