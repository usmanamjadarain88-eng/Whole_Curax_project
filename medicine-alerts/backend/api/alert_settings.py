from utils.vercel_adapter import make_handler
from utils.route_handlers import get_alert_settings, put_alert_settings

class handler(
    make_handler(
        get_fn=get_alert_settings,
        put_fn=put_alert_settings,
    )
):
    pass