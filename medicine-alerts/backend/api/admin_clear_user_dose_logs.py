from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_clear_user_dose_logs


class handler(
    make_handler(
        post_fn=admin_clear_user_dose_logs,
    )
):
    pass
