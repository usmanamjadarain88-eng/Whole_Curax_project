from utils.vercel_adapter import make_handler
from utils.route_handlers import maintenance_run_alert_checks

handler = make_handler(
    post_fn=maintenance_run_alert_checks,
)