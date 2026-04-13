from utils.vercel_adapter import make_handler
from utils.route_handlers import notify_event

class handler(
    make_handler(
        post_fn=notify_event,
    )
):
    pass