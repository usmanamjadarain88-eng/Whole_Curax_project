from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_delete_alerts

class handler(
    make_handler(
        post_fn=admin_delete_alerts,
    )
):
    pass
