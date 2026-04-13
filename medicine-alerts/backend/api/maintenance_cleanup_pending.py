from utils.vercel_adapter import make_handler
from utils.route_handlers import maintenance_cleanup_pending

class handler(
    make_handler(
        post_fn=maintenance_cleanup_pending,
    )
):
    pass