from utils.vercel_adapter import make_handler
from utils.route_handlers import user_account_status

class handler(make_handler(
    get_fn=user_account_status,
)):
    pass