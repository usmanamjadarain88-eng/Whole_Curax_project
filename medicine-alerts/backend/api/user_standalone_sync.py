from utils.vercel_adapter import make_handler
from utils.route_handlers import user_standalone_sync

class handler(
    make_handler(
        post_fn=user_standalone_sync,
    )
):
    pass