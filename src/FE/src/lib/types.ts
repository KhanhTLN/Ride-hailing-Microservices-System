export type Role = "PASSENGER" | "DRIVER" | "ADMIN";
export type VehicleType = "MOTORBIKE" | "CAR";
export interface User {
  id: string;
  email: string;
  display_name: string;
  roles: Role[];
  created_at: string;
}
export interface TokenPair {
  access_token: string;
  refresh_token: string;
  user: User;
  expires_in: number;
}
export interface Point {
  latitude: number;
  longitude: number;
  address?: string;
}
export interface Place extends Point {
  place_id: string;
  display_name: string;
}
export interface Money {
  amount: string;
  currency: string;
}
export interface QuoteInput {
  pickup: Point;
  dropoff: Point;
  vehicle_type: VehicleType;
}
export interface Route {
  distance_m: number;
  duration_s: number;
  geometry: { type: "LineString"; coordinates: [number, number][] };
  vehicle_model: string;
}
export interface Quote extends QuoteInput {
  id: string;
  estimated_distance_m: number;
  estimated_duration_s: number;
  fare: Money;
  expires_at: string;
}
export type TripStatus =
  | "SEARCHING"
  | "DRIVER_OFFERED"
  | "ACCEPTED"
  | "ARRIVED"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "CANCELLED"
  | "NO_DRIVER";
export interface Trip extends QuoteInput {
  id: string;
  passenger_id: string;
  driver_id?: string;
  quote_id: string;
  status: TripStatus;
  payment_status: "PENDING" | "SUCCEEDED" | "FAILED";
  estimated_fare: Money;
  final_fare?: Money;
  version: number;
  attempt_id?: string;
  reservation_id?: string;
  created_at: string;
  updated_at: string;
}
export type TripAction =
  | "accept"
  | "reject"
  | "arrive"
  | "start"
  | "complete"
  | "cancel"
  | "retry-payment";
export interface Operation {
  id: string;
  trip_id: string;
  status: "PENDING" | "SUCCEEDED" | "FAILED";
  type: string;
  error_code?: string;
}
export interface Driver {
  id: string;
  user_id: string;
  license_number: string;
  account_status: "PENDING" | "APPROVED" | "SUSPENDED" | "REJECTED";
  accepting_trips: boolean;
  created_at: string;
}
export interface Vehicle {
  id: string;
  driver_id: string;
  plate_number: string;
  vehicle_type: VehicleType;
  brand: string;
  model: string;
  color: string;
  seat_count: number;
}
export interface MapsConfig {
  tile_url: string;
  motorbike_uses_driving_model: boolean;
}
