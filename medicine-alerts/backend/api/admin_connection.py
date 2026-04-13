from utils.vercel_adapter import make_handler
from utils.route_handlers import get_admin_connection

class handler(
    make_handler(
        get_fn=get_admin_connection,
    )
):
    pass