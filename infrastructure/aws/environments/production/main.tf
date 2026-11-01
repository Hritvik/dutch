module "foundation" {
  source      = "../../modules/foundation"
  environment = "production"
  aws_region  = "ap-south-1"
}
