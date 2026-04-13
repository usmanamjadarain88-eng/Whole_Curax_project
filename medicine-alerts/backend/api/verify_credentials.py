from utils.vercel_adapter import make_handler
from utils.route_handlers import verify_credentials

class handler(
    make_handler(
        post_fn=verify_credentials,
    )
):
    pass