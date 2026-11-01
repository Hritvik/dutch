module "foundation" {
  source      = "../../modules/foundation"
  environment = "staging"
  aws_region  = "ap-south-1"
}
