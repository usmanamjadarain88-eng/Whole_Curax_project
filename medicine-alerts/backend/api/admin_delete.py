from utils.vercel_adapter import make_handler
from utils.route_handlers import delete_admin

handler = make_handler(
    delete_fn=delete_admin,
)