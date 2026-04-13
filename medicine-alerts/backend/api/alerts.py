from utils.vercel_adapter import make_handler
from utils.route_handlers import create_alert, list_alerts

class handler(
    make_handler(
        get_fn=list_alerts,
        post_fn=create_alert,
    )
):
    pass