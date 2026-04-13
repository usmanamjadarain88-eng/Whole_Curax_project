from utils.vercel_adapter import make_handler
from utils.route_handlers import maintenance_run_alert_checks

class handler(make_handler(
    post_fn=maintenance_run_alert_checks,
)):
    pass