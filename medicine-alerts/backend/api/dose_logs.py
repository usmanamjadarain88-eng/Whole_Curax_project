from utils.vercel_adapter import make_handler
from utils.route_handlers import create_dose_log, list_dose_logs

handler = make_handler(
    get_fn=list_dose_logs,
    post_fn=create_dose_log,
)