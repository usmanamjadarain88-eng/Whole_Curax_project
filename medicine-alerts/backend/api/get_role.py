from utils.vercel_adapter import make_handler
from utils.route_handlers import get_role

handler = make_handler(
    get_fn=get_role,
)