from utils.vercel_adapter import make_handler
from utils.route_handlers import save_credentials

class handler(
    make_handler(
        post_fn=save_credentials,
    )
):
    pass