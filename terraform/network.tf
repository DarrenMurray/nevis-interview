# A VPC exists from day one even though the stubbed API needs no private networking.
# Cloud Run reaching a private-IP Cloud SQL instance, and a future UI service talking to
# this API internally, both require it — and retrofitting a network under a running
# service is far more disruptive than creating an idle one now. An unused VPC, subnet and
# IP range cost nothing.

resource "google_compute_network" "main" {
  name                    = "${var.service_name}-vpc"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.required]
}

# Cloud Run direct VPC egress attaches to this subnet. /24 leaves room for the service to
# scale out; direct egress consumes an address per instance.
resource "google_compute_subnetwork" "run" {
  name          = "${var.service_name}-run"
  region        = var.region
  network       = google_compute_network.main.id
  ip_cidr_range = "10.8.0.0/24"

  # Required for Cloud Run direct VPC egress.
  private_ip_google_access = true
}

# --- Private Services Access -------------------------------------------------------
# Reserved range that Google-managed services (Cloud SQL) allocate their private IPs
# from, plus the peering that makes them reachable. Created unconditionally: the
# reservation and peering are free, and they are the slow, fiddly part of adding a
# database later.

resource "google_compute_global_address" "private_services" {
  name          = "${var.service_name}-private-services"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]

  depends_on = [google_project_service.required]
}
