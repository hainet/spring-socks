package lol.maki.socks.order;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import lol.maki.socks.payment.client.AuthorizationRequest;
import lol.maki.socks.payment.client.PaymentApi;
import lol.maki.socks.shipping.client.ShipmentApi;
import lol.maki.socks.shipping.client.ShipmentRequest;
import lol.maki.socks.shipping.client.ShipmentResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.IdGenerator;

@Service
public class OrderService {
	private final PaymentApi paymentApi;

	private final ShipmentApi shipmentApi;

	private final OrderMapper orderMapper;

	private final IdGenerator idGenerator;

	private final Clock clock;

	public OrderService(PaymentApi paymentApi, ShipmentApi shipmentApi, OrderMapper orderMapper, IdGenerator idGenerator, Clock clock) {
		this.paymentApi = paymentApi;
		this.shipmentApi = shipmentApi;
		this.orderMapper = orderMapper;
		this.idGenerator = idGenerator;
		this.clock = clock;
	}

	@Transactional
	public Order placeOrder(URI customerUri, URI addressUri, URI cardUri, URI itemsUri) {
		final String orderId = Order.newOrderId(this.idGenerator::generateId);
		final Order preOrder = Mono.zip(
				this.retrieveCustomer(customerUri),
				this.retrieveAddress(addressUri),
				this.retrieveCard(cardUri),
				this.retrieveItems(itemsUri, orderId).collectList())
				.map(result -> {
					final Customer customer = result.getT1();
					final Address address = result.getT2();
					final Card card = result.getT3();
					final List<Item> items = result.getT4();
					return ImmutableOrder.builder()
							.id(orderId)
							.customer(customer)
							.address(address)
							.card(card)
							.items(items)
							.date(OffsetDateTime.now(this.clock))
							.status(OrderStatus.CREATED)
							.shipment(ImmutableShipment.builder().carrier("dummy").deliveryDate(LocalDate.MIN).trackingNumber(UUID.randomUUID()).build())
							.build();
				})
				.flatMap(order -> {
					final AuthorizationRequest authorizationRequest = new AuthorizationRequest().amount(order.total());
					return this.paymentApi.authorizePayment(authorizationRequest)
							.flatMap(authorizationResponse -> {
								if (authorizationResponse.getAuthorization().getAuthorised()) {
									return Mono.just(order);
								}
								else {
									return Mono.error(new PaymentUnauthorizedException(authorizationResponse.getAuthorization().getMessage()));
								}
							});
				}).block();
		final ShipmentRequest shipmentRequest = new ShipmentRequest().orderId(orderId).itemCount(preOrder.itemCount());
		final ShipmentResponse shipmentResponse = this.shipmentApi.postShipping(shipmentRequest).block();
		try {
			final Order order = ImmutableOrder.builder()
					.from(preOrder)
					.shipment(ImmutableShipment.builder()
							.carrier(shipmentResponse.getCarrier())
							.trackingNumber(shipmentResponse.getTrackingNumber())
							.deliveryDate(shipmentResponse.getDeliveryDate())
							.build())
					.build();
			this.orderMapper.insert(order);
			return order;
		}
		catch (RuntimeException e) {
			// TODO cancel shipment request
			throw e;
		}
	}

	Mono<Customer> retrieveCustomer(URI customerUri) {
		return Mono.just(ImmutableCustomer.builder()
				.id("1234")
				.firstName("John")
				.lastName("Doe")
				.username("jdoe")
				.build());
	}

	Mono<Address> retrieveAddress(URI addressUri) {
		return Mono.just(ImmutableAddress.builder()
				.number("123")
				.street("Street")
				.city("City")
				.country("Country")
				.postcode("1111111")
				.build());
	}

	Mono<Card> retrieveCard(URI cardUri) {
		return Mono.just(ImmutableCard.builder()
				.longNum("4111111111111111")
				.ccv("123")
				.expires(LocalDate.of(2024, 1, 1))
				.build());
	}

	Flux<Item> retrieveItems(URI itemsUri, String orderId) {
		return Flux.just(
				ImmutableItem.builder()
						.itemId("6d62d909-f957-430e-8689-b5129c0bb75e")
						.orderId(orderId)
						.quantity(2)
						.unitPrice(new BigDecimal("17.15"))
						.build(),
				ImmutableItem.builder()
						.itemId("f611b671-40a3-4020-ab7f-68d56a813dc8")
						.orderId(orderId)
						.quantity(1)
						.unitPrice(new BigDecimal("20.00"))
						.build());
	}
}