# Cloud Run reaches Cloud SQL over this network. Retrofitting a VPC under a running
# service is disruptive, and an idle VPC, subnet and IP range cost nothing.

resource "google_compute_network" "main" {
  name                    = "${var.service_name}-vpc"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.required]
}

# Cloud Run direct VPC egress attaches here. Direct egress uses one address per instance,
# so /24 leaves room to scale.
resource "google_compute_subnetwork" "run" {
  name          = "${var.service_name}-run"
  region        = var.region
  network       = google_compute_network.main.id
  ip_cidr_range = "10.8.0.0/24"

  # Required for Cloud Run direct VPC egress.
  private_ip_google_access = true
}

# --- Private Services Access -------------------------------------------------------
# Range Cloud SQL allocates its private IP from, plus the peering that reaches it.

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
