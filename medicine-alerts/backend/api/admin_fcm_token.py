from utils.vercel_adapter import make_handler
from utils.route_handlers import put_admin_fcm_token

handler = make_handler(
    put_fn=put_admin_fcm_token,
)