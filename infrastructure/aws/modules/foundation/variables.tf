variable "environment" {
  description = "Deployment environment represented by this validation-only foundation."
  type        = string
}

variable "aws_region" {
  description = "AWS region selected for the environment."
  type        = string
}

variable "tags" {
  description = "Additional tags that future managed resources must inherit."
  type        = map(string)
  default     = {}
}
