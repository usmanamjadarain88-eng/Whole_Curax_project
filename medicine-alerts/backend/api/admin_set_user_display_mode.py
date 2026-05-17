from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_set_user_display_mode

class handler(
    make_handler(
        post_fn=admin_set_user_display_mode,
    )
):
    pass