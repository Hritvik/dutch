output "environment" {
  value       = var.environment
  description = "The validated deployment environment."
}

output "aws_region" {
  value       = var.aws_region
  description = "The selected AWS region."
}

output "common_tags" {
  value       = local.common_tags
  description = "Mandatory tags for future resource modules."
}
