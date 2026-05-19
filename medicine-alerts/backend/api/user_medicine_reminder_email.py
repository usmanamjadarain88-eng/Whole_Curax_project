from utils.vercel_adapter import make_handler
from utils.route_handlers import user_medicine_reminder_email

handler = make_handler(post_fn=user_medicine_reminder_email)
