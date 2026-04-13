from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_notify

handler = make_handler(
    post_fn=admin_notify,
)