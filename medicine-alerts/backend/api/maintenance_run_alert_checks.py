from utils.vercel_adapter import make_handler
from utils.route_handlers import maintenance_run_alert_checks, maintenance_run_alert_checks_cron

class handler(
    make_handler(
        get_fn=maintenance_run_alert_checks_cron,
        post_fn=maintenance_run_alert_checks,
    )
):
    pass
