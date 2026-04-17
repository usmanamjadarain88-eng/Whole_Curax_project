from utils.vercel_adapter import make_handler
from utils.route_handlers import user_post_display_mode


class handler(
    make_handler(
        post_fn=user_post_display_mode,
    )
):
    pass
