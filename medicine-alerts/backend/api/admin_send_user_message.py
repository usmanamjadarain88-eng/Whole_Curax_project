"""POST /admin/send-user-message — admin → user relay message."""
from utils.route_handlers import admin_send_user_message
from utils.vercel_handler import make_handler

handler = make_handler(post_fn=admin_send_user_message)
