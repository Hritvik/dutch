terraform {
  required_version = ">= 1.8.0"
}

locals {
  common_tags = merge({
    application = "dutch"
    environment = var.environment
    managed_by  = "opentofu"
  }, var.tags)
}

# This module deliberately declares no AWS resources in Slice 0. It validates the
# environment contract without credentials or a remote backend. Resource modules
# are added only after their security and ownership boundaries are approved.
