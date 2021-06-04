package demo.restaurant.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import demo.order.client.OrderServiceClient;
import demo.order.domain.Order;
import demo.restaurant.config.RestaurantProperties;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The restaurant actor drives the state of an order forward after customer creation and until a driver pickup.
 * Each restaurant needs to maintain the state of its capacity to fulfill orderRequests within a specified period of time.
 * <p>
 * For each of the restaurant actors, there are multiple variables that are responsible for determining the
 * supply-demand capacity for fulfilling an online order. In addition to online orderRequests, a restaurant must also fulfill
 * new orderRequests from dine-in customers. The initial state of a chef is dependent on an order fulfillment rate, which
 * represents the chef's average order fulfillment over a period of time. The number and fulfillment rate for each
 * chef actor should be initialized with the restaurant location.
 */
public class Restaurant {

    private final Logger log = Logger.getLogger(this.getClass().getName());
    private RestaurantProperties properties;
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);
    private ScheduledFuture<?> orderScheduler;
    private Long orderCount = 0L;
    private Long orderPreparedTime = 0L;
    private final DeliveryScheduler deliveryScheduler = new DeliveryScheduler();
    private OrderServiceClient orderServiceClient;
    private String city;
    private String name;
    private String country;
    private Double longitude;
    private Double latitude;
    private Integer storeId;

    public Restaurant() {
    }

    public Restaurant(RestaurantProperties properties, OrderServiceClient orderServiceClient) {
        this.properties = properties;
        this.orderServiceClient = orderServiceClient;
    }

    public void init(RestaurantProperties properties, OrderServiceClient orderServiceClient) {
        this.properties = properties;
        this.orderServiceClient = orderServiceClient;
    }

    public ScheduledFuture<?> getOrderScheduler() {
        return orderScheduler;
    }

    public void setOrderScheduler(ScheduledFuture<?> orderScheduler) {
        this.orderScheduler = orderScheduler;
    }

    public ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutor;
    }

    public void orderReceived() {
        orderCount++;

        // Create a new order request with a random order ID
        Order order = orderServiceClient.create(new Order(Math.round(Math.random() * 100000000.0)));

        orderPreparedTime += getFutureTimeFrame(properties.getPreparationRate());

        final long preparedTime = orderPreparedTime.longValue();

        deliveryScheduler
                .addToWorkflow(DeliveryWorkflow.build(deliveryScheduler), order,
                        (event) -> event.setDeliveryTime(deliveryScheduler.getPosition() + getFutureTimeFrame(2.0)),
                        DeliveryEventType.ORDER_ASSIGNED, (orderItem) ->
                                orderServiceClient.assignOrder(orderItem.getOrderId(), this.getStoreId()))
                .addToWorkflow(order,
                        (event) -> event.setDeliveryTime(deliveryScheduler.getPosition() + getFutureTimeFrame(3.0)),
                        DeliveryEventType.ORDER_PREPARING, (orderItem) ->
                                orderServiceClient.prepareOrder(order.getOrderId()))
                .addToWorkflow(order,
                        (event) -> event.setDeliveryTime(preparedTime),
                        DeliveryEventType.ORDER_PREPARED, (orderItem) -> {
                            return orderServiceClient.orderReady(order.getOrderId());
                        })
                .execute();
    }

    private long getFutureTimeFrame(double timeWindowRate) {
        return (1 + ((Math.round(Math.random() * timeWindowRate))));
    }

    public void open() {
        this.close();

        scheduledExecutor.scheduleWithFixedDelay(this::processScheduledEvents,
                properties.getPreparationTime(), properties.getPreparationTime(), TimeUnit.MILLISECONDS);

        this.setOrderScheduler(getScheduledExecutor().scheduleAtFixedRate(this::orderReceived,
                properties.getNewOrderTime(), properties.getNewOrderTime(), TimeUnit.MILLISECONDS));
    }

    private void processScheduledEvents() {
        if (!deliveryScheduler.isEmpty()) {
            List<DeliveryEvent> deliveryEvents = deliveryScheduler.nextFrame();

            if (deliveryEvents != null && deliveryEvents.size() > 0) {
                deliveryEvents.forEach(event -> {
                    Order order = event.getDeliveryAction().apply(event.getOrder());
                    event.setOrder(order);
                    event.getDeliveryWorkflow().setCurrentOrderState(order);
                    event.getDeliveryWorkflow().scheduleNext();
                });

                log.info("[ORDER_EVENT]: " + this.toString() + ": " + Arrays.toString(deliveryEvents
                        .toArray(DeliveryEvent[]::new)));
            }
        }
    }

    public void close() {
        if (orderScheduler != null) {
            orderScheduler.cancel(false);
        }
    }

    public static Restaurant from(RestaurantProperties config, OrderServiceClient orderServiceClient) {
        return new Restaurant(config, orderServiceClient);
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    @JsonProperty("store_id")
    public Integer getStoreId() {
        return storeId;
    }

    public void setStoreId(Integer storeId) {
        this.storeId = storeId;
    }

    @Override
    public String toString() {
        return "Restaurant{" +
                "city='" + city + '\'' +
                ", name='" + name + '\'' +
                ", longitude=" + longitude +
                ", latitude=" + latitude +
                ", storeId=" + storeId +
                '}';
    }
}
