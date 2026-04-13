from utils.vercel_adapter import make_handler
from utils.route_handlers import user_data

handler = make_handler(
    get_fn=user_data,
)