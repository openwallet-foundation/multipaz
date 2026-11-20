package org.multipaz.utopia.knowntypes

/**
 * A checkout as AP2's SDK parses a `checkout_jwt` payload (a UCP Checkout, `ucp/types/checkout.json`):
 * amounts are integer minor units and the currency is at the top, so `4200` is USD 42.00.
 * `DelegateCheckoutSchemaTest` checks that it follows the schemas.
 */
internal const val UCP_CHECKOUT_PAYLOAD = """{
  "id": "chk_1",
  "status": "ready_for_complete",
  "currency": "USD",
  "line_items": [{
    "id": "li_1",
    "item": {"id": "aurora", "title": "Aurora headphones", "price": 4200},
    "quantity": 1,
    "totals": [{"type": "subtotal", "amount": 4200}, {"type": "total", "amount": 4200}]
  }],
  "totals": [{"type": "subtotal", "amount": 4200}, {"type": "total", "amount": 4200}],
  "links": [{"type": "terms_of_service", "url": "https://shop.example.com/terms"}]
}"""
