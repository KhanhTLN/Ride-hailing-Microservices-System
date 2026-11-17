# Kafka event inventory

All keys = trip_id. All topics are v1. Every consumer filters event_type.

| Event | Producer | Consumers | Topic |
|---|---|---|---|
| `TripRequested` | trip | matching | `ds03.trip.events.v1` |
| `MatchingCancelled` | trip | matching | `ds03.trip.events.v1` |
| `DriverReserved` | matching | trip | `ds03.matching.events.v1` |
| `MatchingFailed` | matching | trip | `ds03.matching.events.v1` |
| `DriverOffered` | trip | notification | `ds03.trip.events.v1` |
| `TripAccepted` | trip | notification | `ds03.trip.events.v1` |
| `DriverArrived` | trip | notification | `ds03.trip.events.v1` |
| `TripStarted` | trip | notification | `ds03.trip.events.v1` |
| `TripCompleted` | trip | notification | `ds03.trip.events.v1` |
| `TripCancelled` | trip | notification | `ds03.trip.events.v1` |
| `TripUnmatched` | trip | notification | `ds03.trip.events.v1` |
| `PaymentRequested` | trip | payment | `ds03.payment.commands.v1` |
| `PaymentSucceeded` | payment | trip | `ds03.payment.events.v1` |
| `PaymentFailed` | payment | trip | `ds03.payment.events.v1` |
| `TripPaymentUpdated` | trip | notification | `ds03.trip.events.v1` |
