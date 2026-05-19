"""POST /user/delete-alerts — user app bulk delete."""
from utils.vercel_adapter import make_handler
from utils.route_handlers import user_delete_alerts

handler = make_handler(post_fn=user_delete_alerts)
