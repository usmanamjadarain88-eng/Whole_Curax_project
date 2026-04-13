from utils.vercel_adapter import make_handler
from utils.route_handlers import create_medicine, list_medicines

handler = make_handler(
    get_fn=list_medicines,
    post_fn=create_medicine,
)