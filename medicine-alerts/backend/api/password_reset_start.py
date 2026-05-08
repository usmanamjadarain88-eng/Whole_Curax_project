from utils.vercel_adapter import make_handler
from utils.route_handlers import password_reset_start


class handler(
    make_handler(
        post_fn=password_reset_start,
    )
):
    pass
