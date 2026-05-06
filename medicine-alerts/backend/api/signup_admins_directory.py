from utils.vercel_adapter import make_handler
from utils.route_handlers import signup_list_admins_directory

class handler(
    make_handler(
        get_fn=signup_list_admins_directory,
    )
):
    pass