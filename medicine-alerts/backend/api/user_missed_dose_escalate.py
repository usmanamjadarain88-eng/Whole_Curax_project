from utils.vercel_adapter import make_handler
from utils.route_handlers import user_missed_dose_escalate

handler = make_handler(post_fn=user_missed_dose_escalate)
