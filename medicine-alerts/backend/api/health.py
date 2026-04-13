from utils.vercel_adapter import make_handler
from utils.route_handlers import health

handler = make_handler(
    get_fn=health,
)