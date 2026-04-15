from utils.vercel_adapter import make_handler
from utils.route_handlers import maintenance_cleanup_pending, maintenance_cleanup_pending_cron


class handler(
    make_handler(
        get_fn=maintenance_cleanup_pending_cron,
        post_fn=maintenance_cleanup_pending,
    )
):
    pass